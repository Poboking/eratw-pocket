package com.eratw.port.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * OOM-C 波（2026-11-09）：Android 17 自动堆转储触发器的**纯 JVM 判据**。
 *
 * 钉三件事：
 * ① SDK 守卫：低于 API 37 整条功能关闭（compileSdk 36 上不能引用 API 37 常量）；
 * ② 常量必须来自运行时字段 —— 注入任意两个值都要原样透传，缺任一 / 取值抛掷一律关闭，
 *    绝不猜平台内部数值；
 * ③ 台账行的单行 JSON 形状与转义（路径来自平台，含引号反斜杠也不能拼出畸形行）、
 *    ledgerLine 的缺省与超长截断。
 *
 * `install` 本身要碰 Android 运行时服务，纯 JVM 单测不跑；可判的部分全在这里。
 * 断言签名随 app 模块用 JUnit4（消息在前）。
 */
class ProfilingTriggersTest {

    private fun constants(oom: Int = 17, anomaly: Int = 42): Map<String, Int> = mapOf(
        ProfilingTriggers.OOM_FIELD to oom,
        ProfilingTriggers.ANOMALY_FIELD to anomaly,
    )

    @Test
    fun `低于 API 37 整条功能关闭`() {
        assertNull(ProfilingTriggers.triggerTypes(36) { 1 })
        assertNull(ProfilingTriggers.triggerTypes(35) { 1 })
    }

    @Test
    fun `常量取自运行时字段不硬编码`() {
        val values = constants(oom = 101, anomaly = 202)
        // JUnit4 的 assertNotNull 返回 Unit（不像 kotlin.test 会收窄类型），
        // 故显式保留可空类型再用 !! —— 这一坑在 OOM-A 波已踩过一次。
        val types: IntArray? = ProfilingTriggers.triggerTypes(37) { values[it] }
        assertNotNull(types)
        assertArrayEquals("常量值原样透传", intArrayOf(101, 202), types!!)
    }

    @Test
    fun `缺任一常量即关闭不许只注册一个`() {
        val onlyOom = mapOf(ProfilingTriggers.OOM_FIELD to 17)
        assertNull(ProfilingTriggers.triggerTypes(37) { onlyOom[it] })
        val onlyAnomaly = mapOf(ProfilingTriggers.ANOMALY_FIELD to 42)
        assertNull(ProfilingTriggers.triggerTypes(37) { onlyAnomaly[it] })
        assertNull(ProfilingTriggers.triggerTypes(37) { null })
    }

    @Test
    fun `取常量抛掷时静默关闭`() {
        assertNull(
            ProfilingTriggers.triggerTypes(37) {
                throw IllegalStateException("field missing")
            },
        )
    }

    @Test
    fun `台账行是固定字段序的单行 JSON`() {
        val line = ProfilingTriggers.ledgerEntry(
            nowMs = 1_700_000_000_000L,
            errorCode = 0,
            triggerType = 17,
            path = "/data/user/0/app/heap.hprof",
            bytes = 12_345L,
            errorMessage = null,
        )
        assertEquals(
            "{\"t\":1700000000000,\"code\":0,\"trigger\":17," +
                "\"path\":\"/data/user/0/app/heap.hprof\",\"bytes\":12345,\"error\":\"\"}",
            line,
        )
    }

    @Test
    fun `路径与错误信息里的引号反斜杠换行都转义`() {
        val line = ProfilingTriggers.ledgerEntry(
            nowMs = 1L,
            errorCode = 3,
            triggerType = 42,
            path = "a\"b\\c\nd",
            bytes = -1L,
            errorMessage = "rate\tlimited",
        )
        assertTrue(line, line.contains("a\\\"b\\\\c\\nd"))
        assertTrue(line, line.contains("rate\\tlimited"))
        assertTrue("不许出现裸换行", !line.contains('\n'))
    }

    @Test
    fun `ledgerLine 缺文件为 null 有文件给末行`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "eratw-profiling-test-${System.nanoTime()}")
        val sub = File(dir, "eratw-profiling")
        assertTrue(sub.mkdirs())
        assertNull(ProfilingTriggers.ledgerLine(dir))
        val ledger = File(sub, "ledger.jsonl")
        ledger.writeText("{\"t\":1}\n{\"t\":2}\n", Charsets.UTF_8)
        val line: String? = ProfilingTriggers.ledgerLine(dir)
        assertNotNull(line)
        assertTrue("应读到最后一条记录", line!!.contains("{\"t\":2}"))
        ledger.delete()
        sub.delete()
        dir.delete()
    }

    @Test
    fun `ledgerLine 超长截断不留无界报告行`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "eratw-profiling-long-${System.nanoTime()}")
        val sub = File(dir, "eratw-profiling")
        assertTrue(sub.mkdirs())
        val ledger = File(sub, "ledger.jsonl")
        ledger.writeText("x".repeat(2_000) + "\n", Charsets.UTF_8)
        val line: String? = ProfilingTriggers.ledgerLine(dir)
        assertNotNull(line)
        assertTrue("超长要截断", line!!.contains("超长截断"))
        assertTrue("截断后长度有界：${line.length}", line.length < 400)
        ledger.delete()
        sub.delete()
        dir.delete()
    }
}
