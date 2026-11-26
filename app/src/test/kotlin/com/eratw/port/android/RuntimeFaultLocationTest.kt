package com.eratw.port.android

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行期收口红字头的**裸异常位置后缀**（RECON-STAB-A 接线②的形态补齐项， stab-c 收尾批）。
 *
 * 原版意外分支头串带当前语句位置：`handleException` 取 `current.Position` 格式化成位置串
 * （蛇版 `Process.cs:645-652`）拼进 `HasUnexpectedError` 头串（蛇版 `Process.cs:695`；
 * 文案 `Lang.cs:2994` / zhs `emuera-zhs.xml:744`）。移植侧 [bareFaultLocationSuffix] 补的
 * 就是裸异常缺的那段后缀，位置读数只走 core 已有的只读面 `ProcessRuntime.erbFrameStack`
 * （`Vm.run` 的 catch 在回卷前镜像抛点帧，任意异常离栈都镜像）——不为展示形态给 core 加新面。
 *
 * 钉四件事：
 * 1. **形状**：最内帧 → `（函数名 行N）`（N = 1 基物理行，走 `erbDisplayLine` 单一换算口），
 *    三语格式与 `scriptLocation` 的 CodeEE 后缀同构；
 * 2. **真跑裸异常**：DT_CELL_SET 少值实参的裸 IllegalStateException 原样穿出 VM 边界
 *    （A2 钉桩的逃逸形态）之后，`erbFrameStack` 真的给出带函数名与 0 基行的抛点帧 ——
 *    后缀含物理行，红字头从「只有类型 + 消息」变成「类型 + 消息 + 位置」；
 * 3. **诚实降级**：无帧 / 名与行都读不出 = 空串，头串退回原形，不造假位置；
 * 4. **反向证明**（源码哨兵）：接线钉在生产源码上 —— `handleRuntimeFault` 必须经
 *    `runtimeThrowFrame` 取帧并拼后缀、取数口必须读 `erbFrameStack` 且 CodeEE 家族先行
 *    短路（家族路红字头一字不动）。拆掉任何一环本文件就红。
 */
class RuntimeFaultLocationTest {

    private fun frame(function: String?, line: Int): ProcessRuntime.ErbFrame =
        ProcessRuntime.ErbFrame(
            functionIndex = 0,
            function = function,
            line = line,
            pc = 0,
            callSiteFunction = null,
            callSiteLine = -1,
            callSitePc = -1,
        )

    // ------------------------------------------------------------------
    // 形状：与 scriptLocation 的 CodeEE 后缀同构，三语一致
    // ------------------------------------------------------------------

    @Test
    fun `裸异常帧后缀是函数名加一基物理行`() {
        // 0 基行 1 → 物理 2（erbDisplayLine 单一换算口，不在打印点自己加一）。
        assertEquals("（FN 行2）", bareFaultLocationSuffix(frame("FN", 1), ShellFailureCopy.ZH))
        assertEquals("(FN line 2)", bareFaultLocationSuffix(frame("FN", 1), ShellFailureCopy.EN))
        assertEquals("（FN 2 行目）", bareFaultLocationSuffix(frame("FN", 1), ShellFailureCopy.JA))
    }

    @Test
    fun `行未知时只给函数名`() {
        assertEquals("（FN）", bareFaultLocationSuffix(frame("FN", -1), ShellFailureCopy.ZH))
    }

    @Test
    fun `名不可读时只给行`() {
        assertEquals("（行7）", bareFaultLocationSuffix(frame(null, 6), ShellFailureCopy.ZH))
    }

    // ------------------------------------------------------------------
    // 诚实降级：无帧 / 名与行都读不出 = 空串（不造假位置）
    // ------------------------------------------------------------------

    @Test
    fun `无帧与名行全缺时降级为空串`() {
        assertEquals("", bareFaultLocationSuffix(null, ShellFailureCopy.ZH))
        assertEquals("", bareFaultLocationSuffix(frame(null, -1), ShellFailureCopy.ZH))
        assertEquals("", bareFaultLocationSuffix(frame("  ", -1), ShellFailureCopy.ZH))
    }

    // ------------------------------------------------------------------
    // 真跑裸异常：VM 边界逃逸后 erbFrameStack 给出抛点帧（core 只读面真的可用）
    // ------------------------------------------------------------------

    // 表 + 不可空整数列 + 带值一行（DtBareEscapeVmBoundaryTest 同款 fixture；
    // DT_CELL_SET 少值实参 = A2 钉的裸 IllegalStateException 逃逸形态）。
    // 行号按生产口径给 0 基（ErbFrameStackExportTest 同款），DT_CELL_SET 在下标 6。
    private val bareEscapeScript = listOf(
        "@SYSTEM_TITLE",
        "#DIMS L_DTNAME",
        "L_DTNAME = \"T\"",
        "DT_CREATE L_DTNAME",
        "DT_COLUMN_ADD L_DTNAME , \"n\" , \"int64\" , 0",
        "DT_ROW_ADD L_DTNAME , \"n\" , 7",
        "DT_CELL_SET L_DTNAME , 0 , \"n\"",
    )

    private fun parseErbLines(vararg lines: String): List<Statement> {
        val p = LineParser(
            SymbolTable().declareVariable("L_DTNAME", OperandType.STR, 1),
        )
        return lines.mapIndexedNotNull { i, raw ->
            val text = raw.trimStart()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "bare-escape-test.erb", i, text))
        }
    }

    private fun buildRuntime(mainLines: List<String>): ProcessRuntime {
        val c = ErbProgramCompiler()
        c.compileFunction(
            FuncSig("SYSTEM_TITLE", 0, isMethod = false, paramNames = emptyList()),
            parseErbLines(*mainLines.toTypedArray()),
            emptySet(),
            ExprCallTable(),
        )
        val program: Program = c.build("SYSTEM_TITLE")
        val table = FunctionTable(
            program.functions.mapIndexed { i, f ->
                FuncMeta(
                    index = i,
                    key = f.name.uppercase(),
                    isMethod = false,
                    returnsStr = false,
                    argIntCount = f.arity,
                    argStrCount = f.arity,
                )
            },
        )
        val store = VariableStore().also {
            it.declareUserVar(DimParser.parse("#DIMS", "L_DTNAME", isPrivate = false))
        }
        return ProcessRuntime(program, store, table, VmConfig(maxStepsPerRun = 200_000L))
    }

    /** 裸逃逸驱动（app 模块单测走 JUnit 4，不用 kotlin.test.assertFailsWith —— 手写 try/catch）。 */
    private fun bareEscape(rt: ProcessRuntime): IllegalStateException = try {
        rt.execute()
        error("DT_CELL_SET 少值实参必须抛裸 IllegalStateException（A2 钉桩的逃逸形态）")
    } catch (e: IllegalStateException) {
        e
    }

    @Test
    fun `裸异常穿出VM边界后抛点帧在场且带函数名与零基行`() {
        val rt = buildRuntime(bareEscapeScript)
        val e = bareEscape(rt)
        assertTrue("A2 钉桩的逃逸形态：裸异常不许折成 CodeEE", e !is CodeEE)

        val frames = rt.erbFrameStack()
        assertTrue("裸异常离栈必须留下抛点镜像（erbFrameStack 只读面）", frames.isNotEmpty())
        assertEquals("SYSTEM_TITLE", frames[0].function)
        assertEquals(6, frames[0].line)
    }

    @Test
    fun `裸异常红字头后缀含函数名与物理行`() {
        val rt = buildRuntime(bareEscapeScript)
        val e = bareEscape(rt)
        val suffix = bareFaultLocationSuffix(rt.erbFrameStack().firstOrNull(), ShellFailureCopy.ZH)

        // 头串 = 类型 + 消息 + 位置后缀：接线后裸异常红字头真的带上脚本位置。
        assertEquals("（SYSTEM_TITLE 行7）", suffix)
        val header = "${e.javaClass.name}: ${e.message ?: ""}" + suffix
        assertTrue("头串应含位置（物理行 = 0 基 6 + 1），实际：$header", header.contains("SYSTEM_TITLE 行7"))
    }

    // ------------------------------------------------------------------
    // 反向证明：接线钉在生产源码上（拆掉 = 本节红）
    // ------------------------------------------------------------------

    /**
     * 源码哨兵（口径同 `RuntimeFaultActionTest` 的 `codeLines("GameHost.kt")`）：
     * 1. `handleRuntimeFault` 必须先试 `scriptLocation(t)`（CodeEE 路径一字不动）、
     *    空串时经 `bareFaultLocationSuffix(runtimeThrowFrame(t), failureCopy)` 拼裸异常后缀
     *    （该接线形状全文只此一处）；
     * 2. `runtimeThrowFrame` 必须 CodeEE 家族先行短路（家族路红字头不变）并读
     *    `session?.runtime?.erbFrameStack()`（core 已有只读面，不许另起炉灶）。
     */
    @Test
    fun `位置后缀接线钉在handleRuntimeFault与取数口上`() {
        val host = codeLines("GameHost.kt")
        val start = host.indexOf("private fun handleRuntimeFault(t: Throwable, trace: LoadTrace)")
        assertTrue("handleRuntimeFault 必须在（找不到 = 执行期收口被删）", start >= 0)
        val end = host.indexOf("private fun sqlDriverFor", start)
        assertTrue(end > start)
        val body = host.substring(start, end)
        assertTrue(
            "CodeEE 路径先行：scriptLocation 的既有口径不许被挪走（$body）",
            body.contains("scriptLocation(t).ifEmpty { bareFaultLocationSuffix(runtimeThrowFrame(t), failureCopy) }"),
        )
        assertEquals(
            "裸异常后缀的接线形状全文只此一处（backToTitle 复活线程维持失败屏，不接红字头）",
            1,
            host.countOccurrences("bareFaultLocationSuffix(runtimeThrowFrame(t), failureCopy)"),
        )
        val readStart = host.indexOf("private fun runtimeThrowFrame(t: Throwable)")
        assertTrue("runtimeThrowFrame 取数口必须在（找不到 = 裸异常位置面被拆）", readStart >= 0)
        val readBody = host.substring(readStart, host.indexOf("private fun heapShortageDuringLoad", readStart))
        assertTrue(
            "CodeEE 家族必须先行短路（家族路红字头一字不动）：$readBody",
            readBody.contains("findFirstCauseOfType<CodeEE>()"),
        )
        assertTrue(
            "取数口必须走 core 已有只读面 erbFrameStack（不许给 core 加新面）：$readBody",
            readBody.contains("session?.runtime?.erbFrameStack()?.firstOrNull()"),
        )
    }

    // ------------------------------------------------------------------
    // 取源码（口径同 RuntimeFaultActionTest）
    // ------------------------------------------------------------------

    private val moduleRootCandidates = listOf(File("."), File("../app"), File("app"))

    private fun moduleRoot(): File =
        moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
            ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    /** 主源文件的**代码行**（丢掉注释与 KDoc）：源码面钉子判的是接线，不是文档措辞。 */
    private fun codeLines(name: String): String =
        File(moduleRoot(), "src/main/kotlin/com/eratw/port/android/$name")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            .joinToString("\n")

    private fun String.countOccurrences(needle: String): Int {
        var index = 0
        var count = 0
        while (true) {
            val at = indexOf(needle, index)
            if (at < 0) return count
            count++
            index = at + needle.length
        }
    }
}
