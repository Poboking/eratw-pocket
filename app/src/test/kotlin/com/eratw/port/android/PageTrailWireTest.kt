package com.eratw.port.android

import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.diag.PageFunctionTrail
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本页函数流水」app 侧接线（[PageTrailWire]）的定向单测（fix-pagetrailwire）。
 *
 * ## 钉的是什么
 *
 * core 侧 [PageFunctionTrail] 已在 `obs-pagefuncs` 落定（进帧三处写入 + 页边界先交付后清零），
 * 但**没人挂它** ⇒ `longturn` 那一行只有栈顶。本路的三件交付都在这份用例里出口：
 * 1. **挂上才有读数**：`QueueInputSink` 缺省 `pageTrail == null`（零行为）；挂上之后，
 *    交付回调里（清零之前）读到的是**本页**流水，回调返回之后环归零。
 * 2. **不挂逐字不变**：同一条 `submit → readAnyKey` 序列，挂与不挂都恰好交付一条读数、
 *    都恰好消费一条提交 —— 流水只加观察，不改等待点语义。
 * 3. **长页钉行带流水、末行判据不受影响**：`longturn` 行格式（页中带 `heap=`、页尾不带）、
 *    名字解析失败降级 `#<下标>`（不许静默空），以及「钉行不是状态行」——
 *    `RunTrail.isDirtyExit` 判的是状态文件原文，钉行插进轨迹尾也不改变状态判断与尾部截断。
 *
 * ## 为什么不测真机形状
 *
 * 真机 `longturn` 带 `page:` 的实际读数需要产物 + Activity（本机 `adb devices` 为空）。
 * 本文件只证明判式与格式；真机读数记 TODO(verify)。
 */
class PageTrailWireTest {

    private val names = mapOf(0 to "BEGIN_FLOW", 1 to "ERH_MAIN", 3 to "CHARA_MOVEMENT")

    private fun trailOf(cap: Int = 8, resolve: (Int) -> String? = { names[it] }): PageFunctionTrail =
        PageFunctionTrail(cap).also { it.bindNameResolver(resolve) }

    // ------------------------------------------------------------------
    // 族①：挂上才有读数、不挂逐字不变
    // ------------------------------------------------------------------

    @Test
    fun 缺省不挂即零行为() {
        // core 的挂载点缺省 null（`Vm.pageTrail` / `QueueInputSink.pageTrail`）：没人挂 = 不记录。
        assertNull(QueueInputSink().pageTrail)
    }

    @Test
    fun 反射挂载点的字段名仍在原地() {
        // attachVm 全靠反射；改名在桌面上不会炸（只让流水静默挂不上），所以在这里判红。
        val sinkField = QueueInputSink::class.java.getDeclaredField(PageTrailWire.VM_FIELD)
        assertEquals(PageFunctionTrail::class.java, sinkField.type)
        val vmField = VirtualMachine::class.java.getDeclaredField(PageTrailWire.VM_FIELD)
        assertEquals(PageFunctionTrail::class.java, vmField.type)
        // `ProcessRuntime.vm` 是 attachVm 唯一取到 VM 的口（private，故只做签名哨兵）。
        assertEquals(VirtualMachine::class.java, ProcessRuntime::class.java.getDeclaredField("vm").type)
    }

    @Test
    fun 挂上之后回调里读到本页流水且回调返回后才清零() {
        val trail = trailOf()
        val sink = QueueInputSink()
        PageTrailWire.attachSink(sink, trail)
        assertEquals("挂的是同一个实例", trail, sink.pageTrail)

        // 玩家提交 → VM 跑这一页（进帧写进流水）→ 走到等待点。
        sink.submit("go")
        trail.record(0)
        trail.record(1)
        assertEquals("BEGIN_FLOW → ERH_MAIN", trail.readout())

        var delivered = 0
        var seenInsideCallback = ""
        sink.onTurnReady = { ms ->
            delivered++
            assertTrue("交付读数不该是负的", ms >= 0L)
            // 清零发生在回调**返回之后**（`QueueInputSink` 的页边界 try/finally）：
            // 此刻读到的必须是刚结束那一页。
            seenInsideCallback = trail.readout()
        }
        sink.readAnyKey(anyKey = true, stopMesSkip = false)

        assertEquals(1, delivered)
        assertEquals("回调里读的是本页（清零之前）", "BEGIN_FLOW → ERH_MAIN", seenInsideCallback)
        assertEquals("交付之后页边界清零", 0, trail.size)
        assertEquals("", trail.readout())
        assertEquals("等待点语义没动：一条提交被吃掉", 1L, sink.lastConsumedSeq)
    }

    @Test
    fun 不挂时同一条等待点照旧交付且不碰流水() {
        val trail = trailOf()
        val sink = QueueInputSink()
        assertNull("不挂 = 零行为", sink.pageTrail)

        sink.submit("go")
        // 只由 VM 记录（模拟进帧）；sink 不参与。
        trail.record(0)
        assertEquals(1, trail.size)

        var delivered = 0
        sink.onTurnReady = { ms ->
            delivered++
            assertTrue("不挂也要照旧交付读数", ms >= 0L)
        }
        sink.readAnyKey(anyKey = true, stopMesSkip = false)

        assertEquals("交付条数与挂上时逐字相同", 1, delivered)
        assertEquals("没挂就没有页边界清零", 1, trail.size)
        assertEquals("BEGIN_FLOW", trail.readout())
        assertEquals(1L, sink.lastConsumedSeq)
    }

    @Test
    fun 装配代码不许回到惰性函数表解析名字() {
        // `.erbc` 产物档的 `Program.functions` 是未命中就地解码的惰性表，解码缓冲没有锁
        // （`ErbcStream.kt` 的 SectionWindow / StreamStringTable 都是实例字段）；DevTools
        // daemon 线程读流水会与 VM 线程抢同一路解码（判词见 `ErrorReportVmStackTest`），
        // 还会把 FunctionInfo 整摊物化（整池 15.6 MiB 常驻）。只允许走运行期函数表。
        for (name in listOf("GameHost.kt", "DevToolsHost.kt")) {
            val code = sourceOf(name).lines().filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }.joinToString("\n")
            assertFalse("$name 的代码行不许读 program.functions", code.contains("program.functions"))
        }
    }

    // ------------------------------------------------------------------
    // 族②：长页钉行带流水、末行判据不受影响
    // ------------------------------------------------------------------

    @Test
    fun 长页钉行的格式逐字固定() {
        // 页中压力检查点那一发：既有前缀逐字保留，新增的只是行尾 `| page:` 段。
        assertEquals(
            "longturn 12000ms @CHARA_MOVEMENT heap=505MiB/512MiB | page: BEGIN_FLOW → ERH_MAIN",
            PageTrailWire.longTurnLine(12_000L, "CHARA_MOVEMENT", "BEGIN_FLOW → ERH_MAIN", "505MiB/512MiB"),
        )
        // 页尾交付那一发：格式同源、不带 heap 段。
        assertEquals(
            "longturn 58515ms @- | page: -",
            PageTrailWire.longTurnLine(58_515L, "-", ""),
        )
    }

    @Test
    fun 名字解析不出来时保留下标不写空() {
        // 未绑解析器 ⇒ core 的 readout 给 `#<下标>`；本路只把「这一页没有进帧」写成 `-`。
        val trail = PageFunctionTrail(4)
        trail.record(3)
        assertEquals("#3", trail.readout())
        assertTrue(
            "降级口径不许改成静默空",
            PageTrailWire.longTurnLine(6_000L, "-", trail.readout()).endsWith("| page: #3"),
        )
        assertEquals("-", PageTrailWire.trailText(""))
    }

    @Test
    fun 解析器自己出事也只降级那一格() {
        val trail = trailOf(4) { if (it == 1) error("boom") else names[it] }
        trail.record(0)
        trail.record(1)
        assertEquals("BEGIN_FLOW → #1", trail.readout())
    }

    @Test
    fun 末行判据不受影响且钉行不是状态行() {
        val state = RunTrail.runningState("build-1")
        val pin = PageTrailWire.longTurnLine(58_515L, "CHARA_MOVEMENT", "BEGIN_FLOW → ERH_MAIN")
        assertTrue("状态原文照旧判脏退出", RunTrail.isDirtyExit(state))
        assertFalse("状态原文照旧不判后台回收", RunTrail.isBackgroundDeath(state))
        assertFalse("钉行不能被脏退出判据误认成状态行", RunTrail.isDirtyExit(pin))
        assertFalse("钉行也不能被后台回收判据误认", RunTrail.isBackgroundDeath(pin))

        // 70 行轨迹 + 那一条钉行：报告尾部仍是最后 64 行，钉行占最后一格，状态原文逐字不变。
        val trail = (0 until 70).map {
            RunTrail.formatLine(1_000L + it, 480L, 512L, 88L, "turn 2ms")
        } + pin
        val lines = RunTrail.dirtyReportLines(state, trail)
        assertEquals("状态原文行逐字不变", "状态文件原文：$state", lines[1])
        assertEquals("尾部截断仍是 64 行", 64, lines.size - 3)
        assertEquals("钉行进了尾部最后一格", pin, lines.last())
        assertTrue("脏退出判据读的是状态文件，不是轨迹末行", RunTrail.isDirtyExit(lines[1].removePrefix("状态文件原文：")))
    }

    /** 取 app 主源码文本（工作目录按 AGP 单测惯例是本模块目录）。 */
    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }
}
