package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.frontend.RenameDictionary
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * **真实第三方口上包 → 生产导入链**（[ModImportController.import]）回归闸。
 *
 * 单元面的 I6/poison 判据各自成篇，但它们叠在真包上会不会「一道闸过了、下一道整包炸」
 * 只有真包能回答（KI-GLOBAL-DIM 波实测：越过全局 `#DIM` 闸后撞 `KI-POISON-CASCADE`，
 * 屏上「未预期异常」）。本闸把「玩家的导入动作」原样跑一遍：
 * 源字节 → 生产 [ModImportController.import]（符号表灌装 + PatchCompiler + 转码 +
 * packageId 派生 + 三件套落盘 + 读回）→ 断言 `Imported`。
 *
 * ## 环境门（缺变量 Assume 跳过，不假绿）
 *
 * - `ERATW_ERBC_DIR`：fresh `program.erbc` + `program.meta`（compiler-digest 与当前树一致）；
 * - `ERATW_GAMEDIR`：该产物对应的游戏根（只读；`CSV/` 供重命名表与常量折叠）；
 * - `ERATW_REAL_PKG_DIRS`：**分号分隔**的散装包目录清单（目录内递归收 `.ERB`/`.ERH`，
 *   按文件名扁平化——与 SAF 直选多文件的形态一致）。第三方文本不入库，故只用路径。
 *
 * 每包一个独立临时游戏根：包与包之间的同名函数/产物互不串味（真机上也不会一次导两包）。
 */
class RealPkgPatchImportProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Test
    fun `真实包散装导入——生产导入链返回 Imported`() {
        val erbcDir = env("ERATW_ERBC_DIR")?.let(::File)
        val gameDir = env("ERATW_GAMEDIR")?.let(::File)
        val pkgDirs = env("ERATW_REAL_PKG_DIRS")
            ?.split(';')
            ?.filter { it.isNotBlank() }
            ?.map(::File)
            .orEmpty()
        assumeTrue(
            "需要 ERATW_ERBC_DIR / ERATW_GAMEDIR / ERATW_REAL_PKG_DIRS 三门",
            erbcDir != null && gameDir != null && pkgDirs.isNotEmpty() &&
                File(erbcDir, "program.erbc").isFile && File(erbcDir, "program.meta").isFile &&
                File(gameDir, "CSV").isDirectory && pkgDirs.all { it.isDirectory },
        )
        // 生产保真度第二门：`[[替换名]]` 的展开开关来自游戏根 `emuera.config`
        // （`USE _RENAME.CSV FILE`，4.981 出厂 YES）。没有它就没法复刻生产导入形态，
        // 与其静默降级成「替换名层不启动」的失真场景，不如跳过。
        assumeTrue(
            "需要游戏根 emuera.config（[[替换名]] 展开门控的来源）",
            File(gameDir!!, "emuera.config").isFile,
        )
        val base = baseContext(erbcDir!!, gameDir!!)
        for (pkg in pkgDirs) {
            val sources = pkg.walkTopDown()
                .filter { it.isFile && it.extension.uppercase() in ERB_EXTENSIONS }
                .sortedBy { it.name }
                .map { PickedModFile(it.name, it.readBytes()) }
                .toList()
            assertTrue("${pkg.name} 里没有 .ERB/.ERH", sources.isNotEmpty())

            val gameRoot = tmp.newFolder("game-${pkg.name.hashCode().toUInt()}")
            // **生产保真度（2026-11-27 实测教训）**：导入链的 `[[替换名]]` 展开按
            // `emuera.config` 的 `USE _RENAME.CSV FILE` 门控（spec: Process.cs:261-274；
            // 原版缺省 false，4.981 出厂 `emuera.config:2` 是 YES）。空临时根 = 开关缺省 false
            // ⇒ 替换名层不启动 ⇒ 口上包里 `CALL CHARA_TEXT([[慧音]], …)` 的 `[[` 直漏词法器
            // （LexicalAnalyzer.cs:809-813）⇒ 整批函数被逐条作废。那是**探针环境失真**，
            // 不是生产行为，故把游戏根配置文件原样搬进来。
            File(gameDir, "emuera.config").takeIf { it.isFile }?.copyTo(File(gameRoot, "emuera.config"))
            val controller = ModImportController(
                modStore = ModStore(File(gameRoot, "mods")),
                baseSource = { base },
                gameRoot = gameRoot,
            )
            val outcome = controller.import(sources)
            val detail = (outcome as? ModImportOutcome.Imported)?.detail
            println(
                "REAL-PKG ${pkg.name}: sources=${sources.size} -> " +
                    if (detail == null) {
                        "FAILED $outcome"
                    } else {
                        "IMPORTED functions=${detail.functionCount} overrides=${detail.overrideCount} " +
                            "warnings=${detail.parseWarnings.size}"
                    },
            )
            assertTrue("${pkg.name} 应导入成功，实际: $outcome", detail != null)
            assertTrue("${pkg.name} 至少要编出一个函数，实际: ${detail!!.functionCount}", detail.functionCount > 0)
            // 替换名层必须真的在跑：判据**不是**「零条 `[[` 告警」（第三方包自带悬空键，
            // 如蕾米莉亚 v1.4.1 的 `[[油炸跳蛋]]` 不在 `CSV/_Rename.csv`，原版同形
            // `CanNotRenameKey` 后该函数作废），而是「**字典里已定义的键**不该还残留在源码里」：
            // 读行层的替换是全串替换，只要开关开、字典在，已定义键不可能漏到词法器。
            // 漏了 = `USE _RENAME.CSV FILE` 门控或 `CSV/_Rename.csv` 丢链，
            // 届时口上包会被逐函数作废却仍报「导入成功」。
            val definedKeys = RenameDictionary.load(File(gameDir, "CSV").toPath())?.keys.orEmpty()
            val renameBlind = detail.parseWarnings.filter { w -> definedKeys.any { it in w } }
            assertTrue(
                "${pkg.name} 有 ${renameBlind.size} 个函数的 [[替换名]] 命中字典却未展开（门控/字典丢链），" +
                    "首个：${renameBlind.firstOrNull()?.take(200)}",
                renameBlind.isEmpty(),
            )
            // 作废/降级必须留痕：告警逐条上屏（I7「不许静默少一块」）。
            for (warning in detail.parseWarnings) println("  WARN ${pkg.name}: ${warning.take(300)}")
        }
    }

    /**
     * 与 [ProductionBaseCompileContextSource] 同源同序的上下文（只把产物目录显式指到
     * `ERATW_ERBC_DIR`，其余字段读法逐字相同）：mmap 取六计数、流式 sha256、侧车签名段。
     */
    private fun baseContext(erbcDir: File, gameDir: File): BaseCompileContext {
        val metaFile = File(erbcDir, "program.meta")
        val programFile = File(erbcDir, "program.erbc")
        val program = ProgramLoader.loadMapped(programFile.toPath())
        return BaseCompileContext(
            metadata = BootMetadata.read(metaFile.toPath()),
            baseCounts = PatchCompiler.BaseCounts(
                instructionCount = program.instructionCount,
                functionCount = program.functions.size,
                statementCount = program.statementCount,
                stringCount = program.stringCount,
                longCount = program.constantCount,
                exprCount = program.exprCount,
            ),
            baseSha256 = ProductionBaseCompileContextSource.fileSha256Hex(programFile),
            csvDir = File(gameDir, "CSV"),
            baseSignatures = ModChainAssembly.signatureDirectoryOfFile(metaFile),
        )
    }

    private companion object {
        val ERB_EXTENSIONS = setOf("ERB", "ERH")
    }
}
