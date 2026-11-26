package com.eratw.port.android

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装载失败读数的解析面（task-2 ①）：只认装配链那两行 `mod.install` 上报，成功清旧账、
 * 失败记摘要、脏输入不死人、跨线程读到完整快照、长期运行有上界。
 *
 * 行格式的真源在 `ModRuntimeAssembly`，那边有整行断言钉着；这里只按同一形状喂入。
 */
class ModManageDiagnosticsTest {

    @After
    fun tearDown() {
        ModManageDiagnostics.reset()
    }

    private fun failLine(packageId: String, reason: String): String =
        "mod.install pkg=$packageId active=false failure=$reason"

    private fun okLine(packageId: String): String =
        "mod.install pkg=$packageId active=true"

    @Test
    fun `失败行记下包名与异常摘要`() {
        ModManageDiagnostics.record(failLine("kojo-1a2b3c4d", "IllegalStateException: boom"))

        assertEquals(
            mapOf("kojo-1a2b3c4d" to "IllegalStateException: boom"),
            ModManageDiagnostics.snapshot(),
        )
    }

    @Test
    fun `成功行清掉同包旧失败`() {
        ModManageDiagnostics.record(failLine("eratw.broken", "IllegalArgumentException: 坏清单"))
        assertEquals(1, ModManageDiagnostics.snapshot().size)

        ModManageDiagnostics.record(okLine("eratw.broken"))

        assertTrue("成功装载后旧失败读数必须清掉", ModManageDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun `同包失败只留最后一条 不同包各留一条`() {
        ModManageDiagnostics.record(failLine("eratw.a", "First: 1"))
        ModManageDiagnostics.record(failLine("eratw.a", "Second: 2"))
        ModManageDiagnostics.record(failLine("eratw.b", "Other: 3"))

        assertEquals(2, ModManageDiagnostics.snapshot().size)
        assertEquals("Second: 2", ModManageDiagnostics.snapshot().getValue("eratw.a"))
        assertEquals("Other: 3", ModManageDiagnostics.snapshot().getValue("eratw.b"))
    }

    @Test
    fun `不认识的行走静默忽略`() {
        ModManageDiagnostics.record("mod.rt2 装配完成")
        ModManageDiagnostics.record("mod.diag seq=1 domain=x pkg=eratw.a key=k outcome=REJECTED")
        ModManageDiagnostics.record("mod.install pkg=eratw.a")
        ModManageDiagnostics.record("mod.install pkg= active=false")
        ModManageDiagnostics.record("mod.install pkg=../evil active=false")
        ModManageDiagnostics.record("garbage")

        assertTrue("非装载行不该进失败表：${ModManageDiagnostics.snapshot()}", ModManageDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun `失败但没带 failure 段时给占位原因`() {
        ModManageDiagnostics.record("mod.install pkg=eratw.nullreason active=false")

        assertEquals(
            ModManageDiagnostics.UNKNOWN_REASON,
            ModManageDiagnostics.snapshot().getValue("eratw.nullreason"),
        )
    }

    @Test
    fun `原因超长截断且换行压成空格`() {
        val long = "E".repeat(ModManageDiagnostics.MAX_REASON_CHARS + 50) + "\n尾部"

        ModManageDiagnostics.record(failLine("eratw.long", long))

        val reason = ModManageDiagnostics.snapshot().getValue("eratw.long")
        assertEquals(ModManageDiagnostics.MAX_REASON_CHARS, reason.length)
        assertFalse("换行不该带进单行读数", reason.contains('\n'))
    }

    @Test
    fun `失败读数有上界 超出丢最早的那个包`() {
        val total = ModManageDiagnostics.MAX_TRACKED_PACKAGES + 1
        repeat(total) { index -> ModManageDiagnostics.record(failLine("eratw.p$index", "boom")) }

        val snapshot = ModManageDiagnostics.snapshot()
        assertEquals(ModManageDiagnostics.MAX_TRACKED_PACKAGES, snapshot.size)
        assertNull("第一个包该被挤出有界表", snapshot["eratw.p0"])
        assertTrue("最后一个包必须还在", snapshot.containsKey("eratw.p${total - 1}"))
    }

    @Test
    fun `forget 只摘掉指定包`() {
        ModManageDiagnostics.record(failLine("eratw.a", "boom"))
        ModManageDiagnostics.record(failLine("eratw.b", "boom"))

        ModManageDiagnostics.forget("eratw.a")

        assertEquals(setOf("eratw.b"), ModManageDiagnostics.snapshot().keys)
    }

    @Test
    fun `脏输入与并发写入都不会抛 读到的是完整快照`() {
        val failed = AtomicBoolean(false)
        val start = CountDownLatch(1)
        val threads = (0 until 4).map { worker ->
            Thread {
                start.await()
                repeat(200) { index ->
                    runCatching {
                        ModManageDiagnostics.record(failLine("eratw.t$worker-$index", "boom $index"))
                        ModManageDiagnostics.record(okLine("eratw.t$worker-$index"))
                        ModManageDiagnostics.record(failLine("", "坏包名"))
                        ModManageDiagnostics.record("断行")
                    }.onFailure { failed.set(true) }
                }
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        repeat(500) {
            ModManageDiagnostics.snapshot().forEach { (key, value) ->
                if (key.isBlank() || value.isBlank()) failed.set(true)
            }
        }
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(10)) }

        assertFalse("诊断面在并发/脏输入下抛了异常", failed.get())
        assertTrue("写线程应已结束", threads.all { !it.isAlive })
    }
}
