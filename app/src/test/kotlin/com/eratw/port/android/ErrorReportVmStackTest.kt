package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VmStack] 与「崩溃线程不许解码产物」这条铁律的定向单测。
 *
 * ## 钉的是什么
 *
 * 错误报告会在 `Thread.setDefaultUncaughtExceptionHandler` 里被**崩溃线程**（多半是主线程）
 * 采集，那一刻 VM 线程还活着。原先帧名走 `session.program.functions`，而 `.erbc` 产物档那张表
 * 是「未命中就地解码映射页」的惰性表，解码缓冲是**没有锁的实例字段**（同文件的语句池有
 * `materializeLock`，函数表这一路没有）。并发踩它的两种后果——把 VM 线程的解码踩坏（双死、
 * 报告丢）、把两条记录的串字节混成一个假名字——都只能在真机上出现，桌面怎么跑都是绿的。
 * 所以这里做两件事：
 * 1. 把纯格式化（帧序、截断、降级文本）钉死；
 * 2. 用源码哨兵（与 [RegexIcuParityTest] 同族：桌面闸认证不了设备行为，只能静态补位）
 *    钉死「采集函数名不再读惰性函数表」。
 */
class ErrorReportVmStackTest {

    private fun resolver(names: Map<Int, String>): (Int) -> String? = { names[it] }

    // ---- 帧序与截断 ----

    @Test
    fun `#0 是最内帧且逐帧带函数名`() {
        val funcStack = intArrayOf(10, 20, 30)
        val lines = VmStack.frameLines(funcStack, depth = 3, maxFrames = 25, resolve = resolver(mapOf(10 to "OUTER", 20 to "MIDDLE", 30 to "INNER")))
        assertEquals(listOf("#0 INNER", "#1 MIDDLE", "#2 OUTER"), lines)
    }

    @Test
    fun `栈深为负或表为空时出空表`() {
        // depth 是栈顶帧下标（起跑时 = 0，unwindScopes 之后才收成 -1），不是帧数。
        assertTrue("unwind 之后 depth 会被收成负数", VmStack.frameLines(intArrayOf(1), depth = -1, maxFrames = 25, resolve = resolver(emptyMap())).isEmpty())
        assertTrue(VmStack.frameLines(IntArray(0), depth = 4, maxFrames = 25, resolve = resolver(emptyMap())).isEmpty())
        assertTrue(VmStack.frameLines(intArrayOf(1), depth = 1, maxFrames = 0, resolve = resolver(emptyMap())).isEmpty())
    }

    @Test
    fun `超过上限只保最内N帧并补一行省略说明`() {
        val names = (0 until 10).associateWith { "F$it" }
        val funcStack = IntArray(10) { it }
        val lines = VmStack.frameLines(funcStack, depth = 10, maxFrames = 3, resolve = resolver(names))
        assertEquals(listOf("#0 F9", "#1 F8", "#2 F7", "…（共 10 帧，余下省略）"), lines)
    }

    @Test
    fun `栈深大于表长时按表长收口不越界`() {
        val lines = VmStack.frameLines(intArrayOf(7), depth = 5, maxFrames = 25, resolve = resolver(mapOf(7 to "SOLO")))
        assertEquals(listOf("#0 SOLO"), lines)
    }

    // ---- 降级：读不到名字就如实说读不到 ----

    @Test
    fun `函数名读不出时留函数下标而不是问号`() {
        val lines = VmStack.frameLines(intArrayOf(41, 42), depth = 2, maxFrames = 25, resolve = { null })
        assertEquals(listOf("#0 fn#42（名不可读）", "#1 fn#41（名不可读）"), lines)
    }

    @Test
    fun `解析器自己出事也只降级这一帧不抛出`() {
        val lines = VmStack.frameLines(intArrayOf(3), depth = 1, maxFrames = 25, resolve = { error("boom") })
        assertEquals(listOf("#0 fn#3（名不可读）"), lines)
    }

    @Test
    fun `未登记帧的负下标不送去查表`() {
        val asked = ArrayList<Int>()
        val lines = VmStack.frameLines(intArrayOf(5, -1), depth = 2, maxFrames = 25, resolve = { i -> asked += i; "F$i" })
        assertEquals(listOf("#0 ${VmStack.SLOT_UNSET_PREFIX}", "#1 F5"), lines)
        assertEquals("负下标不是函数下标，不该进解析器", listOf(5), asked)
    }

    // ---- 源码哨兵：崩溃路径不得再走惰性解码面 ----

    @Test
    fun 采集器的代码不再读惰性函数表而改读运行期函数表() {
        // 只看代码行：KDoc 里当然要写明「原来读 program.functions 会怎样」，那不是违例。
        val code = collectorSource().lines().filterNot { line ->
            val t = line.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString("\n")
        assertFalse(
            "崩溃线程读 program.functions 会触发未加锁的映射页解码（与 VM 线程抢同一条惰性路）",
            code.contains("program.functions"),
        )
        assertFalse("帧名不许再走 FunctionInfo 面", code.contains("FunctionInfo"))
        assertTrue(
            "帧名应走 FunctionTable.byFunctionIndex（数组读，不碰映射页）",
            code.contains("byFunctionIndex"),
        )
    }

    /** 取 `ErrorReportCollector.kt` 源文本（工作目录按 AGP 单测惯例是本模块目录）。 */
    private fun collectorSource(): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt"),
            File("../app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 ErrorReportCollector.kt（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    // ---- 反射依赖的字段名：改名要在这里判红，不许在崩溃路径上静默降级 ----

    @Test
    fun 采集器反射要读的core字段名仍在原地() {
        // 崩溃路径全靠反射取这三个字段；改名在桌面上不会炸（采集降级成一行说明），
        // 只会让报告永远读不到调用栈 —— 所以把名字钉在这里。
        val table = com.eratw.port.core.proc.ProcessRuntime::class.java.getDeclaredField("table")
        assertEquals(com.eratw.port.core.proc.FunctionTable::class.java, table.type)
        com.eratw.port.core.proc.ProcessRuntime::class.java.getDeclaredField("vm")
        val vm = com.eratw.port.core.code.VirtualMachine::class.java
        assertEquals(IntArray::class.java, vm.getDeclaredField("funcStack").type)
        assertEquals(Int::class.javaPrimitiveType, vm.getDeclaredField("depth").type)
        // 取面用的 public 读口：崩溃线程只许走这条不碰映射页的路。
        assertEquals(
            com.eratw.port.core.proc.FuncMeta::class.java,
            com.eratw.port.core.proc.FunctionTable::class.java
                .getMethod("byFunctionIndex", Int::class.javaPrimitiveType)
                .returnType,
        )
        // 抛点镜像的读口（2026-11-01）：实时帧表空了以后就靠它，改名同样只会在设备上静默降级。
        assertEquals(
            IntArray::class.java,
            com.eratw.port.core.code.VirtualMachine::class.java
                .getMethod("funcStackAtThrow").returnType,
        )
    }

    // ---- 抛点镜像回落：只给「带异常的报告」用，且必须自带口径说明 ----

    @Test
    fun 采集器在实时帧表回卷后回落抛点镜像但脏退出报告不回落() {
        // `run()` 的 finally 把 depth 收成 -1 ⇒ 报告写的时候实时帧恒空（端点 2026-11-01
        // 现存 100 份里 93 份就是这样）。回落三件事都在采集器里，缺一不可：
        //   ① 读镜像（getMethod("funcStackAtThrow")），
        //   ② 本报告没有 throwable 时**不许**回落（否则「上次异常退出」会挂上上一局的链），
        //   ③ 帧链前面垫一行说明，把「抛点镜像」与「实时帧表」两种读数分开。
        val code = collectorSource()
        assertTrue("回落要读 core 的抛点镜像口", code.contains("funcStackAtThrow"))
        assertTrue("没有异常的报告不许回落镜像", Regex("""if\s*\(\s*error\s*==\s*null\s*\)\s*emptyList\(\)""").containsMatchIn(code))
        assertTrue("镜像帧链要垫一行口径说明", code.contains("THROW_MIRROR_MARK"))
    }

    @Test
    fun 镜像帧行复用实时帧的格式化且不放开深度上限() {
        // 镜像下标序与实时 funcStack 一致（最外层在前），所以深度交回 size-1 就能出 #0=最内帧；
        // 上限仍走 ErrorReport.STACK_MAX_LINES —— 报告在堆将尽时生成，这一节的形状不许变宽。
        val mirror = intArrayOf(10, 20, 30)
        val names = mapOf(10 to "OUTER", 20 to "MIDDLE", 30 to "INNER")
        val lines = VmStack.frameLines(mirror, mirror.size - 1, com.eratw.port.core.diag.ErrorReport.STACK_MAX_LINES, resolver(names))
        assertEquals(listOf("#0 INNER", "#1 MIDDLE", "#2 OUTER"), lines)
    }
}
