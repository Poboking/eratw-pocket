package com.eratw.port.platform.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HostDiagnostics.statementPoolAuditEnabled] 的判据：审计只在 debuggable 包执行。
 *
 * 依据（2026-10-22 平台层审计）：`GameHost.assembleSession` 的
 * `runCatching { auditStatementPoolAt(program, programFile) }` 在 release 启动路径上无条件
 * 执行，而 `auditStatementPoolAt` 内部还会 `ProgramLoader.loadMapped` 两次（第二/第三实例），
 * 每次都是完整解码并各持一份 `Program` 与只读 `FileChannel`（core 无 `Program.close`）。
 * release 启动因此多付两份常驻，正压在 512 MiB 堆机型装载峰值最紧的窗口。
 */
class HostDiagnosticsTest {

    @Test
    fun `release 包不执行语句池审计`() {
        assertFalse(
            "release（FLAG_DEBUGGABLE=0）必须跳过两次额外 loadMapped",
            HostDiagnostics.statementPoolAuditEnabled(debuggable = false),
        )
    }

    @Test
    fun `debug 包照常执行语句池审计`() {
        assertTrue(
            "debug 包保留 2026-10-22 死循环复盘的 pool-audit 现场",
            HostDiagnostics.statementPoolAuditEnabled(debuggable = true),
        )
    }
}
