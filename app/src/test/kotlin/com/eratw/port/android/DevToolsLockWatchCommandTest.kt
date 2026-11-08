package com.eratw.port.android

import com.eratw.port.core.diag.LockWatch
import com.eratw.port.core.host.LineBufferConsoleSink
import java.io.File
import org.junit.After
import org.junit.Assert
import org.junit.Test

/**
 * DevTools 的 `lockwatch` 命令（OBS2-L2，契约 §2 L2 第 4 项）的行为面钉子。
 *
 * 钉四件事，逐条对着契约的要求：
 * 1. **`on` → `readout` 有读数**：命令打开后真跑一遍挂载点（`LineBufferConsoleSink` 的
 *    写侧入列 + 读侧快照 = [LockWatch.ID_SINK_DISPLAY]），`readout` 里必须出现 `sink.display`
 *    那一行；
 * 2. **`off` → 挂载点回到零行为**：摘除后再跑同一遍，样本总数一条都不涨
 *    （守卫是 [LockWatch.handleOrNull]，关时一次 volatile 布尔读，不取时钟、不进监视器）；
 * 3. **命令面绝不抛**：空参、乱参、大小写与空白都必须回一行文本，不许抛异常；
 * 4. **缺省关 + release 不可达**这两格不许被后来人偷偷改掉：源码面钉子
 *    （全 app 装配面只有这一处能 attach；`startOnce` 的 debuggable 闸必须还在）。
 *
 * 本类只调 [LockWatchCommand]（纯 JVM 逻辑），不碰 Android API —— `DevToolsHost` 的构造要
 * `GameHost` + `CanvasConsoleView`，JVM 单测里造不出来（同 `PageTrailWireTest` 的分工口径）；
 * 命令语义全在这个对象里，`DevToolsHost.execute` 只多一条 `when` 分支（第 4 条用例把
 * 那条分支与 USAGE 一起钉住）。
 */
class DevToolsLockWatchCommandTest {

    private val savedEnabled = LockWatch.enabled

    @After
    fun restoreWatch() {
        LockWatch.enabled = savedEnabled
        LockWatch.reset()
    }

    // ---- JUnit4 的 Assert 是「消息在前」，本文件按仓内 kotlin.test 习惯写消息在后，这里转一次 ----

    private fun assertTrue(condition: Boolean, message: String) = Assert.assertTrue(message, condition)

    private fun assertFalse(condition: Boolean, message: String) = Assert.assertFalse(message, condition)

    private fun assertEquals(expected: Any?, actual: Any?, message: String) =
        Assert.assertEquals(message, expected, actual)

    private fun assertEquals(expected: Any?, actual: Any?) = Assert.assertEquals(expected, actual)

    /** 真跑一遍 `sink.display` 的两个临界区（写侧一次入列 + 读侧一次快照），返回显示行数。 */
    private fun runDisplayMount(): Int {
        val sink = LineBufferConsoleSink(clientWidth = 40)
        sink.printSingleLine("lockwatch 命令面自检行")
        return sink.lines().size
    }

    @Test
    fun `on 之后 readout 有读数 off 之后挂载点回到零行为`() {
        LockWatch.detach()
        LockWatch.reset()

        val on = LockWatchCommand.execute("on")
        assertTrue(on.startsWith("lockwatch on"), "on 的回执要能认出是自己：$on")
        assertTrue(LockWatch.enabled, "on 必须真的挂载（attach 走的就是这一档）")

        // 挂载态下跑两遍挂载点：写侧 + 读侧各两条样本
        assertEquals(1, runDisplayMount(), "装置本身要有效：一次 printSingleLine 应落 1 行")
        assertEquals(1, runDisplayMount())
        val readout = LockWatchCommand.execute("readout")
        assertTrue(readout.contains("sink.display"), "readout 必须有 sink.display 那一行：\n$readout")
        assertTrue(readout.contains("[lockwatch] 挂载=on"), "表头要写明挂载态：\n$readout")
        assertTrue(LockWatch.hasSamples(LockWatch.ID_SINK_DISPLAY), "采样器里要有这个挂载点的账")
        val whileOn = LockWatch.totalSamples()
        assertTrue(whileOn >= 4L, "两遍挂载点应至少 4 条样本（写 2 + 读 2），实读 $whileOn")

        // ② off：回到零行为 —— 再跑同一遍，样本总数一条都不涨
        val off = LockWatchCommand.execute("off")
        assertTrue(off.startsWith("lockwatch off"), "off 的回执要能认出是自己：$off")
        assertFalse(LockWatch.enabled, "off 必须真的摘除")
        val before = LockWatch.totalSamples()
        assertEquals(1, runDisplayMount())
        assertEquals(1, runDisplayMount())
        assertEquals(before, LockWatch.totalSamples(), "摘除后挂载点必须回到零行为（一条都不许多记）")
        assertTrue(
            LockWatchCommand.execute("readout").contains("[lockwatch] 挂载=off"),
            "摘除后表头要写明 off",
        )
    }

    @Test
    fun `reset 只清读数不动挂载态`() {
        LockWatch.attach()
        runDisplayMount()
        assertTrue(LockWatch.totalSamples() > 0L, "装置没记到东西 ⇒ 本条断言是空的")
        val text = LockWatchCommand.execute("reset")
        assertTrue(text.startsWith("lockwatch reset"), "reset 回执：$text")
        assertEquals(0L, LockWatch.totalSamples(), "reset 必须清表")
        assertTrue(LockWatch.enabled, "reset 不许顺手把探针摘了（摘除是 off 的活）")
    }

    @Test
    fun `命令面绝不抛 空参与乱参都回文本`() {
        for (arg in listOf("", "   ", "status", "help", "?", "ON", " off ", "bogus", "readout extra")) {
            val out = LockWatchCommand.execute(arg)
            assertTrue(out.isNotEmpty(), "空回执（arg=$arg）等于把命令通道关上了")
            assertFalse(out.startsWith("EXC"), "命令面抛出去了（arg=$arg）：$out")
        }
        assertTrue(LockWatchCommand.execute("bogus").contains(LockWatchCommand.HELP), "乱参要回用法行")
        assertTrue(LockWatchCommand.execute("").contains(LockWatchCommand.HELP), "不带参要回用法行")
    }

    @Test
    fun `缺省关且只有这一条命令能挂载`() {
        // ① DevToolsHost 的 when 里必须有这条分支，USAGE 清单要同步补一行。
        val devtools = appSource("DevToolsHost.kt")
        assertTrue(
            "\"lockwatch\" -> LockWatchCommand.execute(arg)" in devtools,
            "DevToolsHost.execute 少了 lockwatch 分支 ⇒ 真机出口断了",
        )
        assertTrue("  lockwatch [on|off|readout|reset|status]" in devtools, "USAGE 清单没补这一行")
        // ② 全 app 装配面只允许 LockWatchCommand 里那一发 attach（缺省关的判据）。
        val sources = appMainSources()
        val attaches = sources.filter { "LockWatch.attach()" in it.second }
        assertEquals(
            listOf("DevToolsHost.kt"),
            attaches.map { it.first },
            "装配期出现第二处 LockWatch.attach() ⇒ 「缺省关」这条纪律破了（观测面不许自己开门）",
        )
        val enableWrites = sources.filter { Regex("LockWatch\\.enabled\\s*=").containsMatchIn(it.second) }
        assertTrue(
            enableWrites.isEmpty(),
            "app 侧直接写 LockWatch.enabled 的文件 ${enableWrites.map { it.first }} —— 开关只走 attach/detach 两个口",
        )
    }

    /**
     * release 包里这条命令**打不到**的判据钉子（契约 §2 L2 第 5 项的答卷本体）。
     *
     * 通道的启动闸在 `startOnce` 的第一行：非 debuggable 直接 return ⇒ 轮询线程不起、
     * `cmd.txt` 没人读 ⇒ `lockwatch` 对玩家/正式作者不可达。这一行有人删了就是
     * 「把调试通道放进正式包」，必须在这里红。
     */
    @Test
    fun `调试通道的 debuggable 闸还在（release 打不到这一格的前提）`() {
        val devtools = appSource("DevToolsHost.kt")
        assertTrue(
            "applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return" in devtools,
            "startOnce 的 debuggable 闸不见了 —— 要么通道被放开进 release（不许），" +
                "要么这条命令的可达性口径要重写（回 LockWatch 类 KDoc「谁能把它打开」改）",
        )
    }

    private fun appSource(name: String): String {
        for (base in listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))) {
            val f = File(base, "com/eratw/port/android/$name")
            if (f.isFile) return f.readText()
        }
        throw AssertionError("读不到 app 源文件 $name（工作目录 ${File(".").absolutePath}）")
    }

    /** app 主源集的全部 .kt（文件名 → 文本），供「只此一处 attach」这类全局钉子。 */
    private fun appMainSources(): List<Pair<String, String>> {
        val root = listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("找不到 app/src/main/kotlin")
        return root.walk().filter { it.isFile && it.extension == "kt" }
            .map { it.name to it.readText() }
            .toList()
    }
}
