package com.eratw.port.android

import com.eratw.port.core.code.CallStackOverflow
import com.eratw.port.core.code.LoopGuardCause
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.err.CodeEE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [runtimeFaultAction] 收口分级的口径（RECON-STAB-A A1 修复的验收判据，纯 JVM 单测）：
 *
 * 1. **运行期非 CodeEE → 执行期收口**：会话已建立（脚本泵已进入）后穿出的意外异常按
 *    原版 DoScript 宽捕获同构收口——红字上屏、会话保留、报告归「脚本意外错误」，
 *    不再误用「启动失败」屏与销毁会话（判据 = [RuntimeFaultAction.RUNTIME_FAULT]）。
 * 2. **装载期 → 启动失败屏不变**：脚本泵未进入（会话未建立）的故障一律维持既有
 *    [RuntimeFaultAction.LOAD_FAULT] 收口——包括装载期 CodeEE 与 Error。
 * 3. **反向证明**（最后一节源码哨兵）：把 `loadAndRun` 的 catch 从判据分流改回无条件
 *    `handleLoadOrRuntimeFault`（即「关掉新分级」），判据函数再绿行为也照旧 —— 所以
 *    接线本身钉在生产源码上：判据必须在 catch 处被调用、`pumpEntered` 必须在 drive
 *    之前置位、`handleRuntimeFault` 必须不调 `shutdownForExit`。删掉任何一环本文件就红。
 *
 * 判据细节（为什么非可恢复 CodeEE 与 Error 维持装载收口）见 `RuntimeFaultAction` 的 KDoc。
 */
class RuntimeFaultActionTest {

    // ------------------------------------------------------------------
    // 方向一：运行期（会话已建立）非 CodeEE → 执行期收口
    // ------------------------------------------------------------------

    /** 执行期裸 RuntimeException 是 A1 的主形态：必须分流到执行期收口。 */
    @Test
    fun `运行期非CodeEE异常分级为执行期收口`() {
        assertEquals(
            RuntimeFaultAction.RUNTIME_FAULT,
            runtimeFaultAction(closed = false, pumpEntered = true, RuntimeException("分歧C形态")),
        )
        assertEquals(
            RuntimeFaultAction.RUNTIME_FAULT,
            runtimeFaultAction(closed = false, pumpEntered = true, IllegalStateException("半死行NPE形态")),
        )
        assertEquals(
            RuntimeFaultAction.RUNTIME_FAULT,
            runtimeFaultAction(closed = false, pumpEntered = true, IndexOutOfBoundsException("DT列集越界形态")),
        )
    }

    /** 执行期收口不吞：判据只分流，异常本身照常携带（类型与消息都在）。 */
    @Test
    fun `执行期收口不改变异常事实`() {
        val t = RuntimeException("现场消息")
        val action = runtimeFaultAction(closed = false, pumpEntered = true, t)
        assertEquals(RuntimeFaultAction.RUNTIME_FAULT, action)
        assertEquals("现场消息", t.message)
    }

    // ------------------------------------------------------------------
    // 方向二：装载期 → 启动失败屏不变（一字不动）
    // ------------------------------------------------------------------

    /** 脚本泵未进入：任何异常（含 CodeEE 与 Error）都维持装载失败收口。 */
    @Test
    fun `装载期异常维持启动失败收口`() {
        assertEquals(
            RuntimeFaultAction.LOAD_FAULT,
            runtimeFaultAction(closed = false, pumpEntered = false, RuntimeException("装载期意外")),
        )
        assertEquals(
            RuntimeFaultAction.LOAD_FAULT,
            runtimeFaultAction(closed = false, pumpEntered = false, CodeEE("装载期脚本错")),
        )
        assertEquals(
            RuntimeFaultAction.LOAD_FAULT,
            runtimeFaultAction(closed = false, pumpEntered = false, OutOfMemoryError("装载期堆")),
        )
    }

    /**
     * 非可恢复 CodeEE 穿出执行期（DeviceBudget / CallStackOverflow 被 drive 与泵重抛的那条）
     * 照旧走装载失败收口 —— 登记形态（现场转储挂装载收口）不动，这是 A1 的边界不是回归。
     */
    @Test
    fun `执行期非可恢复CodeEE照旧走装载失败收口`() {
        assertEquals(
            RuntimeFaultAction.LOAD_FAULT,
            runtimeFaultAction(closed = false, pumpEntered = true, CodeEE("执行期脚本错")),
        )
        assertEquals(
            RuntimeFaultAction.LOAD_FAULT,
            runtimeFaultAction(
                closed = false,
                pumpEntered = true,
                StepLimitExceeded(
                    steps = 1L, pc = 0, line = 0, funcName = "F",
                    guardCause = LoopGuardCause.DeviceBudget,
                ),
            ),
        )
        assertEquals(
            RuntimeFaultAction.LOAD_FAULT,
            runtimeFaultAction(
                closed = false,
                pumpEntered = true,
                CallStackOverflow(depth = 1, line = 0, pc = 0, funcName = null),
            ),
        )
    }

    /** 执行期 Error 维持装载失败收口：内存族失败屏与 StackOverflowError 登记判定不改。 */
    @Test
    fun `执行期Error照旧走装载失败收口`() {
        assertEquals(
            RuntimeFaultAction.LOAD_FAULT,
            runtimeFaultAction(closed = false, pumpEntered = true, OutOfMemoryError("执行期堆")),
        )
        assertEquals(
            RuntimeFaultAction.LOAD_FAULT,
            runtimeFaultAction(closed = false, pumpEntered = true, StackOverflowError()),
        )
    }

    // ------------------------------------------------------------------
    // 关闭竞态：静默收口（与 handleLoadOrRuntimeFault 的 closed 首查同口径）
    // ------------------------------------------------------------------

    /** 宿主已 close：不分相位不分型，一律静默 —— 关闭竞态是正常退出路径。 */
    @Test
    fun `关闭竞态一律静默收口`() {
        assertEquals(
            RuntimeFaultAction.SUPPRESS_BY_CLOSE,
            runtimeFaultAction(closed = true, pumpEntered = false, RuntimeException("x")),
        )
        assertEquals(
            RuntimeFaultAction.SUPPRESS_BY_CLOSE,
            runtimeFaultAction(closed = true, pumpEntered = true, RuntimeException("x")),
        )
        assertEquals(
            RuntimeFaultAction.SUPPRESS_BY_CLOSE,
            runtimeFaultAction(closed = true, pumpEntered = true, CodeEE("x")),
        )
    }

    // ------------------------------------------------------------------
    // 反向证明：分级判据的接线钉在生产源码上（关掉判据 = 本节红）
    // ------------------------------------------------------------------

    /**
     * 源码哨兵（口径同 `UiFaultTest` 的 `codeLines("GameHost.kt")` 系列与
     * `ShellUiFailureActionsTest`）：判据函数本身绿不代表接线对 —— catch 改回无条件
     * `handleLoadOrRuntimeFault`（= 关掉分级）行为照旧，所以钉三处接线：
     *
     * 1. catch 里必须按 [runtimeFaultAction] 分流（判据被删 = 红）；
     * 2. `pumpEntered` 必须在 `drive` 之前置位（置位挪到 drive 之后 = 执行期异常全部错判装载期）；
     * 3. [GameHost.handleRuntimeFault] 必须不调 `shutdownForExit`（会话保留是本笔的验收判据，
     *    混进退出清场 = A1 的「会话销毁」差异原样回来）。
     */
    @Test
    fun `分级判据接在装载驱动大块的catch上`() {
        val host = codeLines("GameHost.kt")
        val gate = host.indexOf("runtimeFaultAction(closed, pumpEntered, t)")
        assertTrue("loadAndRun 的 catch 必须经 runtimeFaultAction 分流（找不到 = 分级被关）", gate >= 0)
        assertEquals(
            "主泵这一形状（位置实参）的接线全文只此一处；backToTitle 复活线程的第二处接线" +
                "用命名实参恒传 pumpEntered=true，由 UiFaultTest 的分型哨兵钉",
            1,
            host.countOccurrences("runtimeFaultAction(closed, pumpEntered, t)"),
        )
        val drive = host.indexOf("drive(fresh)")
        val armed = host.indexOf("pumpEntered = true")
        assertTrue("pumpEntered 置位点必须在（找不到 = 判据永远走装载支）", armed >= 0)
        assertTrue(
            "pumpEntered 必须在 drive 之前置位（drive=$drive armed=$armed）：置位晚了执行期异常会错判成装载期",
            armed < drive,
        )
        val runtimeBranch = host.indexOf("RuntimeFaultAction.RUNTIME_FAULT -> handleRuntimeFault(t, trace)")
        assertTrue("RUNTIME_FAULT 支必须接到 handleRuntimeFault（找不到 = 分流后仍落旧收口）", runtimeBranch > gate)
    }

    /** 执行期收口必须会话保留：handleRuntimeFault 全函数不许出现退出清场。 */
    @Test
    fun `执行期收口不销毁会话`() {
        val host = codeLines("GameHost.kt")
        val start = host.indexOf("private fun handleRuntimeFault(t: Throwable, trace: LoadTrace)")
        assertTrue("handleRuntimeFault 必须在（找不到 = 执行期收口被删）", start >= 0)
        val end = host.indexOf("private fun sqlDriverFor", start)
        assertTrue(end > start)
        val body = host.substring(start, end)
        assertFalse("执行期收口不许调退出清场（会话保留是验收判据）：$body", body.contains("shutdownForExit"))
        assertFalse("执行期收口不许关会话：$body", body.contains("session?.close()"))
        assertTrue("执行期收口必须上失败屏（IN_GAME 族，scriptErrorTerminal 同款）：$body", body.contains("FailureScreenKind.IN_GAME"))
        assertTrue("执行期收口必须红字上屏（原版意外分支同形）：$body", body.contains("printError"))
        assertEquals(
            "报告类目「脚本意外错误」全文只此一处（装载收口的「启动失败」类目不许被改）",
            1,
            host.countOccurrences("\"脚本意外错误\""),
        )
        assertTrue(
            "装载收口原样保留（别把装载面改坏）",
            host.contains("private fun handleLoadOrRuntimeFault(t: Throwable, trace: LoadTrace)"),
        )
    }

    // ------------------------------------------------------------------
    // 取源码（口径同 UiFaultTest / LiveReflowWiringTest）
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
