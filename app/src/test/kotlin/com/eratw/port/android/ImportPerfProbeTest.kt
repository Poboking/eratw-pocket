package com.eratw.port.android

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.frontend.RenameDictionary
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.platform.storage.ModStore
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * **导入链分相计时探针**（性能口径，不判业务成败）。
 *
 * 生产导入链一次要碰 382 MB 的 `program.erbc`（流式 sha256 + mmap）与 61 MB 的
 * `program.meta`（侧车解析），再加上 CSV 装载、符号表、PatchCompiler、编码与落盘。
 * 「哪一段是大头」不能靠猜：本探针在真产物上逐段计时，读数打到 stdout，供性能波对照。
 *
 * ## 环境门（缺变量 Assume 跳过，不假绿）
 *
 * - `ERATW_ERBC_DIR`：fresh `program.erbc` / `program.meta`；
 * - `ERATW_GAMEDIR`：对应游戏根（`CSV/` 供 CSV 装载与替换名字典）；
 * - `ERATW_REAL_PKG_DIRS`：**分号分隔**的散装包目录（只取第一只做整链计时）。
 *
 * ## 为什么拆成五个函数
 * 探针本身是一条线性脚本（量十来个点），但一条 90 行的测试方法既过不了行数闸，也让人
 * 分不清「哪几行属于哪一相位」。这里按相位切：产物装载 / 编译细分 / 整链，各段读数各自
 * 独立，改一段不会牵动别的段的计时口径。
 */
class ImportPerfProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 环境门通过后的产物路径集合（三个变量的派生结果，只算一次）。 */
    private class Probe(
        val programFile: File,
        val metaFile: File,
        val csvDir: File,
        val pkgDir: File,
        val gameDir: File,
    )

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Test
    fun `导入链分相计时`() {
        val probe = probeEnvironment()
        val (program, metadata) = probeBaseArtifactPhases(probe)
        val sources = readSources(probe.pkgDir)
        val gameRoot = tmp.newFolder("perf-game")
        File(probe.gameDir, "emuera.config").takeIf { it.isFile }?.copyTo(File(gameRoot, "emuera.config"))
        val controller = newController(probe, gameRoot)
        probeCompilePhases(controller, probe, program, metadata, sources)
        probeFullImportPhases(controller, sources)
    }

    /** 三门环境变量 + 文件存在性；缺一项就 Assume 跳过（不是失败，也不假绿）。 */
    private fun probeEnvironment(): Probe {
        val erbcDir = env("ERATW_ERBC_DIR")?.let(::File)
        val gameDir = env("ERATW_GAMEDIR")?.let(::File)
        val pkgDir = env("ERATW_REAL_PKG_DIRS")?.split(';')?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(
            "需要 ERATW_ERBC_DIR / ERATW_GAMEDIR / ERATW_REAL_PKG_DIRS 三门",
            erbcDir != null && gameDir != null && pkgDir != null &&
                File(erbcDir, "program.erbc").isFile && File(erbcDir, "program.meta").isFile &&
                File(gameDir, "CSV").isDirectory && pkgDir.isDirectory,
        )
        val probe = Probe(
            programFile = File(erbcDir!!, "program.erbc"),
            metaFile = File(erbcDir, "program.meta"),
            csvDir = File(gameDir!!, "CSV"),
            pkgDir = pkgDir!!,
            gameDir = gameDir,
        )
        println(
            "PERF sizes: program.erbc=${probe.programFile.length() / 1024 / 1024}MiB " +
                "program.meta=${probe.metaFile.length() / 1024 / 1024}MiB",
        )
        return probe
    }

    /** 单件读数：base 产物（sha256 / mmap / 侧车）+ 符号表依赖的 CSV 装载。 */
    private fun probeBaseArtifactPhases(probe: Probe): Pair<Program, BootMetadata> {
        time("sha256(program.erbc)") { ProductionBaseCompileContextSource.fileSha256Hex(probe.programFile) }
        val program = time("loadMapped(program.erbc)") { ProgramLoader.loadMapped(probe.programFile.toPath()) }
        time("counts") { baseCountsOf(program) }
        val metadata = time("BootMetadata.read(program.meta)") { BootMetadata.read(probe.metaFile.toPath()) }
        time("knownMethodTypes") { metadata.knownMethodTypes() }
        time("signatureDirectoryOfFile(program.meta)") { ModChainAssembly.signatureDirectoryOfFile(probe.metaFile) }
        time("GameDataLoader.load(CSV)") { GameDataLoader.load(probe.csvDir.toPath()) }
        time("RenameDictionary.load(CSV)") { RenameDictionary.load(probe.csvDir.toPath()) }
        return program to metadata
    }

    /** 选中目录里的散装源（与导入链同口径：只收 `.ERB`/`.ERH`，按文件名排序）。 */
    private fun readSources(pkgDir: File): List<PickedModFile> {
        val sources = pkgDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.uppercase() in setOf("ERB", "ERH") }
            .sortedBy { it.name }
            .map { PickedModFile(it.name, it.readBytes()) }
        println("PERF sources=${sources.size} bytes=${sources.sumOf { it.bytes.size }}")
        return sources
    }

    /**
     * 编译段细账：符号表 → PatchCompiler 构造（建索引）→ compile（解析 + 发射）。
     * 这三段合起来就是导入的 COMPILE 相位，分开量才知道该优化哪一支。
     */
    private fun probeCompilePhases(
        controller: ModImportController,
        probe: Probe,
        program: Program,
        metadata: BootMetadata,
        sources: List<PickedModFile>,
    ) {
        val counts = baseCountsOf(program)
        val symbolBuild = time("buildPatchSymbolTable") {
            controller.buildPatchSymbolTable(metadata, probe.csvDir, useERD = true)
        }
        val renames = RenameDictionary.load(probe.csvDir.toPath())
        val compiler = time("PatchCompiler ctor") {
            PatchCompiler(
                metadata,
                counts,
                symbolBuild.table,
                renameDic = renames,
                constantKeywordLookup = symbolBuild.keywordLookup,
                warnNormalFunctionOverloading = true,
            )
        }
        val patchSources = sources.map { PatchCompiler.SourceFile(relPath = it.displayName, text = it.text) }
        val entry = Regex("(?m)^@([A-Za-z0-9_]+)").find(patchSources.first().text)!!.groupValues[1]
        time("PatchCompiler.compile") { compiler.compile(patchSources, entry) }
    }

    /**
     * 整链两跑：第一跑是冷态（真机形态），第二跑复用同一控制器 —— 差额就是
     * 「base 上下文每次重算」在总时长里占的那一份。
     */
    private fun probeFullImportPhases(controller: ModImportController, sources: List<PickedModFile>) {
        time("import(full, cold)") { controller.import(sources) }
        time("import(full, again)") { controller.import(sources) }
    }

    /** 生产形态的控制器：base 上下文逐次现读（这正是本波要量的那份代价）。 */
    private fun newController(probe: Probe, gameRoot: File): ModImportController {
        var lastStageAt = System.nanoTime()
        var lastStage: ModImportStage? = null
        return ModImportController(
            modStore = ModStore(File(gameRoot, "mods")),
            baseSource = BaseCompileContextSource { root ->
                val p = ProgramLoader.loadMapped(probe.programFile.toPath())
                BaseCompileContext(
                    metadata = BootMetadata.read(probe.metaFile.toPath()),
                    baseCounts = baseCountsOf(p),
                    baseSha256 = ProductionBaseCompileContextSource.fileSha256Hex(probe.programFile),
                    csvDir = File(root, "CSV"),
                    baseSignatures = ModChainAssembly.signatureDirectoryOfFile(probe.metaFile),
                )
            },
            gameRoot = gameRoot,
            onProgress = { progress ->
                if (progress.stage != lastStage) {
                    val now = System.nanoTime()
                    lastStage?.let { println("PERF phase $it = ${(now - lastStageAt) / 1_000_000}ms") }
                    lastStage = progress.stage
                    lastStageAt = now
                }
            },
        )
    }

    private fun baseCountsOf(program: Program): PatchCompiler.BaseCounts = PatchCompiler.BaseCounts(
        program.instructionCount,
        program.functions.size,
        program.statementCount,
        program.stringCount,
        program.constantCount,
        program.exprCount,
    )

    private inline fun <T> time(label: String, block: () -> T): T {
        // 先跑一次让 JIT/页缓存热起来的口径不适用（真机一次导入是冷的），只记首跑。
        val t0 = System.nanoTime()
        val out = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("PERF $label = ${ms}ms")
        return out
    }
}
