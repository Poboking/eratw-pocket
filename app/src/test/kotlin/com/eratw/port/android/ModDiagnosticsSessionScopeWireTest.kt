package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 会话诊断面的接线与边界口径（Lane DXR，task-122）。
 *
 * 三件事：①「归零落在会话装配开始、早于链装配与 attach」用源码序哨兵钉住；②生产 main 里只允许
 * 有这一处归零调用（防再补一处把顺序搞坏的调用点）；③导入面（资源目录名不一致 / zip 被
 * [ModZipGuard] 拒收）**不进**会话诊断面，理由是诊断面只吃 `GameHost` 的 `logSink` 转发
 * （装配期 / 运行期 `mod.install` 行），导入是设置页的独立流程、拒因走导入结果面。
 *
 * 为什么不行为层直接钉 `GameHost`：app 的 JVM 单测面没有 Robolectric，实例化不了 `GameHost`
 * （同 [GameHostSharpWireTest] 的口径），所以「归零先于真装配」拆成两半 —— 源码序由本类钉，
 * 「装配期 canonical 行活过 attach」由 `ModDiagnosticsSessionScopeE2ETest` 组 F 用真 seam 覆盖。
 */
class ModDiagnosticsSessionScopeWireTest {

    @Before
    fun setUp() {
        ModManageDiagnostics.reset()
    }

    @After
    fun tearDown() {
        ModManageDiagnostics.reset()
    }

    // ------------------------------------------------------------------
    // ① 归零落点：assembleSession 顶部，且早于链装配与运行时 attach
    // ------------------------------------------------------------------

    @Test
    fun `会话归零在 assembleSession 顶部 且早于链装配与 attach`() {
        // 只在「代码面」上找调用点：KDoc/注释里提到 `ModManageDiagnostics.reset()` 的地方
        // （例如诊断面口径说明）不是调用，不能被哨兵当成第二处归零。
        val src = codeOnly(sourceOf("GameHost.kt"))
        val assembleAt = src.indexOf("private fun assembleSession(")
        assertTrue("找不到 assembleSession 定义，哨兵失效：${candidatesOf("GameHost.kt")}", assembleAt >= 0)
        val resetAt = src.indexOf("ModManageDiagnostics.reset()")
        assertTrue("GameHost 里找不到会话归零调用（失败行会跨会话残留）", resetAt > assembleAt)
        val chainAt = src.indexOf("ModChainAssembly.assembleWithCatalog(")
        val attachAt = src.indexOf("ModRuntimeAssembly.attach(")
        assertTrue("找不到链装配调用点，哨兵失效", chainAt > 0)
        assertTrue("找不到运行时 attach 调用点，哨兵失效", attachAt > 0)
        assertTrue(
            "归零必须早于链装配：链装配期拒载必 throw（attach 走不到），归零若在其后就丢掉本会话唯一证据",
            resetAt < chainAt,
        )
        assertTrue("归零必须早于运行时 attach：attach 期的 canonical 失败行是本会话事实的主来源", resetAt < attachAt)
        assertEquals("GameHost 内归零调用必须只有一处（第二处随时可把顺序搞坏）", resetAt, src.lastIndexOf("ModManageDiagnostics.reset()"))
    }

    // ------------------------------------------------------------------
    // ② 生产 main 源码里只有 GameHost 这一处归零调用
    // ------------------------------------------------------------------

    @Test
    fun `生产 main 里只有 GameHost 一处调归零`() {
        val dir = mainSourceDir()
        val hits = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name }
            .toList()
        assertTrue("扫不到 main 源码，哨兵失效：$dir", hits.size > 50)
        val callers = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> codeOnly(file.readText()).contains("ModManageDiagnostics.reset(") }
            .map { it.name }
            .toList()
        assertEquals("归零只该由会话装配点调；别处在进程中途清空会抹掉运行中会话的失败行", listOf("GameHost.kt"), callers)
    }

    // ------------------------------------------------------------------
    // ③ 导入面两形态：资源目录名不一致 / zip 被拒收 —— 都不进会话诊断面
    // ------------------------------------------------------------------

    @Test
    fun `资源目录名不一致只走导入告警面 不写也不清会话诊断面`() {
        val tmp = Files.createTempDirectory("eratw-diagscope").toFile()
        val controller = controllerIn(tmp)
        // 预置一条「上一会话」残留：导入流程既不该把它写进诊断面，也不该顺手清掉（清场是会话边界的职责）。
        ModManageDiagnostics.record("mod.install pkg=$STALE_PKG active=false failure=上一会话残留")
        val zip = zipOf(
            SOURCE_NAME to PATCH_SOURCE.toByteArray(Charsets.UTF_8),
            "$MISMATCH_PREFIX.assets/img/a.png" to byteArrayOf(0x89.toByte(), 1, 2, 3),
            "$MISMATCH_PREFIX.assets/b.bin" to byteArrayOf(9, 9),
        )

        val detail = (controller.import(listOf(PickedModFile("kojo_mod.ZIP", zip))) as? ModImportOutcome.Imported)
            ?.detail ?: throw AssertionError("来源目录名不一致按既有口径重挂并告警，导入应成功")
        assertEquals("两条资源都被重挂计数", 2, detail.resourceEntryCount)
        val warnings = detail.resourceWarnings
        assertEquals("重挂汇总成一条 WARN：$warnings", 1, warnings.size)
        assertTrue(
            "WARN 要在导入结果面点名来源目录名（玩家从这儿看到，不是设置页失败小节）：$warnings",
            warnings[0].contains(MISMATCH_PREFIX),
        )
        assertEquals(
            "导入面不得往会话诊断面写行也不得清场（诊断面只吃 GameHost 的 mod.install 转发）",
            setOf(STALE_PKG),
            ModManageDiagnostics.snapshot().keys,
        )
    }

    @Test
    fun `zip 被 ModZipGuard 拒收只走导入失败面 不进会话诊断面`() {
        val tmp = Files.createTempDirectory("eratw-diagscope").toFile()
        val controller = controllerIn(tmp)
        val entries = (0 until ModZipGuard.MAX_ENTRIES + 1).map { "h%03d.ERB".format(it) to "RETURN 0\n".toByteArray() }

        val failed = controller.import(listOf(PickedModFile("huge-pack.ZIP", zipOf(*entries.toTypedArray()))))
            as? ModImportOutcome.Failed ?: throw AssertionError("条目数越过上限必须整包拒导")
        assertEquals("拒导折成导入失败面", ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("失败文案要点名 zip：${failed.message}", failed.message.contains("huge-pack.ZIP"))
        assertTrue("失败文案要点名拒因：${failed.message}", failed.message.contains("条目数超过上限"))
        assertTrue(
            "拒因属于导入面，不进会话诊断面（否则设置页会把导入失败错标成装载失败）",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 夹具：源码定位 + 与 ModImportAssetsTest 同款的最小导入夹具
    // ------------------------------------------------------------------

    private fun candidatesOf(name: String): List<File> = listOf(
        File("src/main/kotlin/com/eratw/port/android/$name"),
        File("../app/src/main/kotlin/com/eratw/port/android/$name"),
    )

    private fun sourceOf(name: String): String {
        val candidates = candidatesOf(name)
        val file = candidates.firstOrNull { it.isFile }
            ?: error("源码不在预期位置（cwd=${File(".").absolutePath}）：$candidates")
        return file.readText()
    }

    /**
     * 去掉块注释与行注释后的源码文本（哨兵只在真实代码面上匹配调用点）。
     *
     * 背景：诊断面口径的 KDoc 会写「`ModManageDiagnostics.reset()` 在会话装配点顶部调用」这类说明；
     * 若哨兵直接扫原文，注释里的同一串就会被当成第二处调用而误红（2026-11-18 实际踩到）。
     */
    private fun codeOnly(text: String): String =
        text.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private fun mainSourceDir(): File = listOf(
        File("src/main/kotlin/com/eratw/port/android"),
        File("../app/src/main/kotlin/com/eratw/port/android"),
    ).firstOrNull { it.isDirectory }
        ?: error("main 源码目录不在预期位置（cwd=${File(".").absolutePath}）")

    private fun controllerIn(tmp: File): ModImportController =
        ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
            gameRoot = tmp,
        )

    private fun miniMetadata(): BootMetadata {
        val names = listOf("A_ONE", "A_TWO", "A_SUM", "A_DEF")
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        return BootMetadata(
            functions = names.mapIndexed { i, name ->
                FuncMeta(
                    index = i,
                    key = name,
                    name = name,
                    isMethod = name == "A_SUM",
                    argIntCount = argCounts[name] ?: 0,
                    argStrCount = 0,
                    returnsStr = false,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
        )
    }

    private fun miniBaseCounts(): PatchCompiler.BaseCounts =
        PatchCompiler.BaseCounts(
            instructionCount = 4000,
            functionCount = 4,
            statementCount = 100,
            stringCount = 10,
            longCount = 5,
            exprCount = 20,
        )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /** 固定的源落盘名（packageId 由它派生，造 zip 前可算）。 */
        const val SOURCE_NAME = "KOJO_PATCH.ERB"

        /** 可编译的最小补丁源（只引用 base 的 A_ONE）。 */
        const val PATCH_SOURCE = "@B_HELLO\nCALL A_ONE(3)\nPRINTL HELLO_PATCH\nRETURN 0\n"

        /** 与导入包本名无关的来源目录名 = 资源目录名不一致形态。 */
        const val MISMATCH_PREFIX = "eratw.demopack"

        /** 预置残留行用的包名（只作读数，不进装配）。 */
        const val STALE_PKG = "mod.stale.old"

        /** 块注释（含 KDoc）：哨兵只扫代码面时整段剥掉。 */
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")

        /** 行注释：剥掉 `//` 起到行尾。 */
        val LINE_COMMENT = Regex("""//[^\n]*""")
    }
}
