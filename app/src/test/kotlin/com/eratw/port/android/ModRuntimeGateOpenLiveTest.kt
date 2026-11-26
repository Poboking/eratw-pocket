package com.eratw.port.android

import com.eratw.port.core.code.Program
import com.eratw.port.core.host.NoopInputSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.GameSession
import com.eratw.port.erbcc.compile.ErbModuleCompiler
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * RT2-GATE-OPEN 的真语料全链探针：**总闸开态下 `.erbm` 函数真的被装载并执行**。
 *
 * ## 这一例要补的缺口
 *
 * 装配链单测（`ModRuntimeAssemblyWireTest` / `ModChainAssembly*Test`）已证明「总闸开关
 * → 装配产物」，但总闸翻成缺省开以后，还缺一条**从设置闸值一路走到真 VM 执行**的读数：
 * 开态必须装、且 mod 侧函数经 `GameSession(modChain = plan)` 真跑出返回值；关态必须
 * 连容器都不读（[ModChainAssembly.assemble] 返回 null）且会话跑的是未改动的 base
 * `Program`。两态都用同一份真 base 语料与同一份 mod 资产，差异只来自闸值。
 *
 * ## 环境门（JUnit 动态用例纪律）
 *
 * `ERATW_MODGATE_LIVE=1` 且 `ERATW_ERBC_DIR` 指向带签名段的 fresh 产物目录
 * （`program.erbc` + `program.meta`）才生成用例；变量缺失时 [rows] 返回空集合，
 * Parameterized 生成 **0 个用例**（不进 XML、不计 skipped，与 Assume 假绿划清界限）。
 * 带变量轮必须 `cleanTest`（test 任务 inputs 不含环境变量，不 fresh 会 UP-TO-DATE 假绿），
 * 且必要时 `./gradlew --stop`（守护进程快照式继承环境变量）。
 *
 * ## 夹具
 *
 * 探针 mod 只含一个**加性**函数（`@ERATW_GATE_PROBE`：一句 PRINTFORML + `RETURN 1`），
 * 不声明覆盖 ⇒ 不经过签名闸；它证明的是「开态 mod 函数进了链接产物并被执行」，覆写面
 * 由 `ModChainAssemblyKojoContentEndToEndTest` 另行证明。真语料与 `program.meta` 只读，
 * `mods/rt2` 落临时根。
 */
@RunWith(Parameterized::class)
class ModRuntimeGateOpenLiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 判据 1：开态 —— 容器真装、mod 函数真执行、屏上有输出
    // ------------------------------------------------------------------

    @Test
    fun `判据1_开态容器真装且mod函数经真VM执行`() {
        val erbc = requireNotNull(erbcDirOrNull()) { "环境门开了但 ERATW_ERBC_DIR 不可用" }
        val base = baseProgram(erbc)
        val metadata = baseMetadata(erbc)
        val root = gameRoot(erbc)

        assertTrue(
            "探针函数不得已存在于 base 语料（加性判据）",
            metadata.functions.none { it.key == PROBE_FUNCTION },
        )
        writeProbeAssets(root)

        val plan = requireNotNull(
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, metadata.functions)),
        ) { "开态必须产出链接产物（容器 + manifest 成对在场）" }
        assertFalse("纯加性不构成覆盖", plan.hasOverrides)
        assertNull("无覆盖走直调快路径", plan.functionDispatch)

        val session = session(root, plan, base, metadata)
        assertSame("进 VM 的 Program 必须是链接产物", plan.program, session.runtime.program)
        assertEquals(
            "开态 mod 函数必须经真 VM 执行并返回 1",
            1L,
            session.runtime.callFunction(PROBE_FUNCTION),
        )
        assertTrue(
            "mod 函数的 PRINTFORML 必须真的上屏：${session.console.loggedLines()}",
            session.console.loggedLines().any { it.contains(PROBE_TEXT) },
        )
    }

    // ------------------------------------------------------------------
    // 判据 2：关态 —— 零接触（连容器都不读）、会话跑未改动的 base
    // ------------------------------------------------------------------

    @Test
    fun `判据2_关态零接触且会话跑未改动的base`() {
        val erbc = requireNotNull(erbcDirOrNull()) { "环境门开了但 ERATW_ERBC_DIR 不可用" }
        val base = baseProgram(erbc)
        val metadata = baseMetadata(erbc)
        val root = gameRoot(erbc)

        // 同一份资产在场：关态必须在读容器之前短路（否则坏资产也会把游戏拖死）。
        writeProbeAssets(root)
        assertNull(
            "关态 assemble 必须返回 null（零接触）",
            ModChainAssembly.assemble(ChainAssemblyInputs(root, false, base, metadata.functions)),
        )

        val session = session(root, null, base, metadata)
        assertSame("关态会话必须跑未改动的 base Program", base, session.runtime.program)
        assertTrue(
            "关态函数表不得出现 mod 函数",
            metadata.functions.none { it.key == PROBE_FUNCTION },
        )
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun baseProgram(erbc: Path): Program =
        ProgramLoader.loadMapped(erbc.resolve("program.erbc"))

    private fun baseMetadata(erbc: Path): BootMetadata =
        BootMetadata.read(erbc.resolve("program.meta"))

    /** 临时游戏根：`erbc.v1/program.meta` 指向真 meta（同卷硬链优先，跨卷退复制）。 */
    private fun gameRoot(erbc: Path): File {
        val root = tmp.newFolder("modgate-live")
        val dst = File(root, "erbc.v1/program.meta")
        dst.parentFile?.mkdirs()
        val src = erbc.resolve("program.meta")
        if (runCatching { Files.createLink(dst.toPath(), src) }.isFailure) {
            Files.copy(src, dst.toPath())
        }
        return root
    }

    /** 写探针 `.erbm` + 同名 manifest（加性：`overrideFunctions` 为空）。 */
    private fun writeProbeAssets(root: File) {
        val lines = listOf(
            "@$PROBE_FUNCTION",
            "PRINTFORML $PROBE_TEXT",
            "RETURN 1",
        )
        val spec = ErbModuleCompiler.specFromErb(
            PACKAGE_ID,
            "$PACKAGE_ID.probe.erb",
            lines,
            emptyMap(),
        )
        val module = ErbModuleCompiler.compile(spec)
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        File(dir, "$PACKAGE_ID.erbm")
            .writeBytes(ErbModuleContainer.encode(module, allowSuperCalls = true))
        File(dir, "$PACKAGE_ID.manifest").writeBytes(
            ManifestCodec.encode(ModuleDescriptor(packageId = PACKAGE_ID, overrideFunctions = emptySet())),
        )
    }

    private fun session(
        root: File,
        plan: ModChainRuntimePlan?,
        base: Program,
        metadata: BootMetadata,
    ): GameSession = GameSession(
        gameRoot = root.toPath(),
        program = base,
        metadata = metadata,
        inputSinkOverride = NoopInputSink,
        modChain = plan,
    )

    private companion object {
        /** env 门：`ERATW_MODGATE_LIVE=1` 才生成用例。 */
        private const val GATE_ENV = "ERATW_MODGATE_LIVE"
        private const val GATE_ON = "1"

        /** 真 base 产物目录（`program.erbc` + `program.meta`）。 */
        private const val ERBC_ENV = "ERATW_ERBC_DIR"

        private const val PACKAGE_ID = "eratw.gate.probe"
        private const val PROBE_FUNCTION = "ERATW_GATE_PROBE"
        private const val PROBE_TEXT = "ERATW_GATE_PROBE_OK"

        private fun env(name: String): String? =
            System.getenv(name)?.takeIf { it.isNotBlank() }

        private fun gateOn(): Boolean = env(GATE_ENV) == GATE_ON

        private fun erbcDirOrNull(): Path? {
            val dir = env(ERBC_ENV)?.let { Path.of(it) } ?: return null
            val usable = Files.isRegularFile(dir.resolve("program.erbc")) &&
                Files.isRegularFile(dir.resolve("program.meta"))
            return if (usable) dir else null
        }

        /**
         * Parameterized 行集：env 门开 + 产物可用 = 一行（全部判据跑一次）；
         * 任一门关 = 空行集 = **0 个用例**（不进 XML、不计 skipped）。
         */
        @JvmStatic
        @Parameterized.Parameters
        fun rows(): Collection<Array<Any>> =
            if (gateOn() && erbcDirOrNull() != null) listOf(arrayOf<Any>()) else emptyList()
    }
}
