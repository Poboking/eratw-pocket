package com.eratw.port.android

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「装载失败上屏」这条链的**装配哨兵**（行为面由 `ModManageDiagnosticsTest` 钉死）。
 *
 * 这里只钉三件在编译期看不出来的事：
 * 1. 宿主 `GameHost` 的 `logSink` 真的把行旁路转给了 [ModManageDiagnostics.record]
 *    （少了这一行，设置页的「装载失败」小节永远是空表，且没有任何测试会红）；
 * 2. 装配链 `ModRuntimeAssembly` 打的两种行仍然长成解析器认识的样子
 *    （`active=true` / `active=false failure=…`）——改格式就等于静默废掉上屏；
 * 3. 端到端：拿装配链**真实的**格式串喂给 `record`，快照里能读出包名与原因。
 */
class ModManageDiagnosticsWireTest {

    @After
    fun tearDown() {
        ModManageDiagnostics.reset()
    }

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `GameHost 的 logSink 旁路转给失败面且原日志行不变`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "GameHost 的 logSink 必须转给 ModManageDiagnostics.record",
            host.contains("ModManageDiagnostics.record(line)"),
        )
        assertTrue(
            "既有日志行格式必须原样保留（真机排障还靠它）",
            host.contains("Log.i(TAG, \"modrt: \$line\")"),
        )
    }

    @Test
    fun `装配链的两种装载行仍被解析器认得出`() {
        val session = sourceOf("ModRuntimeAssembly.kt")
        assertTrue(
            "成功行格式变了会静默废掉「成功清旧账」",
            session.contains("mod.install pkg=\${state.packageId} active=true"),
        )
        assertTrue(
            "失败行格式变了会静默废掉「失败上屏」",
            session.contains("active=false "),
        )
        assertTrue(
            "失败载荷必须带 failure= 段",
            session.contains("failure=\${failure.javaClass.simpleName}: \${failure.message}"),
        )
    }

    @Test
    fun `按装配链真实格式串喂进来能读出包名与原因`() {
        // 与 ModRuntimeAssembly 拼出来的行一字不差（见上一条哨兵）。
        ModManageDiagnostics.record(
            "mod.install pkg=eratw.demo active=false failure=IllegalStateException: boom",
        )
        assertEquals(
            mapOf("eratw.demo" to "IllegalStateException: boom"),
            ModManageDiagnostics.snapshot(),
        )

        // 同包随后成功：旧失败必须被清掉，界面不该继续报旧账。
        ModManageDiagnostics.record("mod.install pkg=eratw.demo active=true")
        assertTrue("成功行必须清掉同包旧失败", ModManageDiagnostics.snapshot().isEmpty())
    }
}
